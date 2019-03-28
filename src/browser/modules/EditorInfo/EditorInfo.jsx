/*
 * This program depicts the behaviour of the edit drawer that displays
 * and edits the properties selected from the canvas.
 */

import React, { Component } from 'react'
import { connect } from 'react-redux'
import { withBus } from 'react-suber'
import * as itemEditorActions from 'shared/modules/itemEditor/itemEditorDuck'
import * as _ from 'lodash'
import { EditNodes } from './EditNodes'

export class EditorInfo extends Component {
  constructor (props) {
    super(props)
    this.state = {
      disabled: true,
      validator: true, // edit not happened / property not changed
      item: props.itemEditor.neo4jItem
        ? _.cloneDeep(props.itemEditor.neo4jItem)
        : undefined
    }
  }

  /**
   * this method is used to dispatch action in reducer
   */

  setEditSelectedItem = () => {
    this.props.setEditSelectedItem(this.state.item)
  }

  // setvalidator= () => {
  //   this.props.setvalidator(this.state.validator)
  // }

  checkChangesSave = e => {
    if (!this.state.validator) {
      if (confirm('Please Save your changes!')) {
        alert('if')
      } else {
        console.log('I m in else')
      }
    }
  }

  /**
   *
   * Changes the props to local state
   */
  componentWillReceiveProps (nextProps) {
    // const nodeChanged =
    //   nextProps.itemEditor.selectedItem.item.id !==
    //   this.props.itemEditor.selectedItem.item.id;
    if (!this.state.validator) {
      alert('save first')
    }
    this.setState({
      item: _.cloneDeep(nextProps.itemEditor.neo4jItem),
      disabled: true,
      validator: true
    })
  }

  componentWillMount (nextProps) {
    console.log('componentWillMount')
    const nodeChanged = nextProps !== this.props
    console.log(nodeChanged)
  }

  /**
   *  This function is used to set item of the state
   * when changes are done while  child component changes data
   *
   */

  setParentItemState = newProperties => {
    this.setState({
      validator: false
    })
    let newstate = _.cloneDeep(this.state)
    console.log(newstate)

    Object.keys(newstate).forEach(function (k) {
      if (newstate[k]) {
        newstate[k]._fields[0] = newProperties
      }
    })
    this.setState(newstate)
  }

  /**
   *
   * Toggle the disable state to handle
   * the edit button
   */
  handleEdit = () => {
    this.setState({
      disabled: !this.state.disabled
    })
  }

  render () {
    return (
      <div>
        <div>
          <EditNodes
            properties_state_data={this.props.itemEditor}
            item={this.state}
            handleEdit={this.handleEdit}
            setEditSelectedItem={this.setEditSelectedItem}
            setParentItemState={this.setParentItemState}
            checkChangesSave={this.checkChangesSave}
            setvalidator={this.props.setvalidator}
            switchNode={this.props.switchNode}
          />
        </div>
      </div>
    )
  }
}

const mapStateToProps = state => {
  return {
    itemEditor: state.itemEditor,
    // validator: state.itemEditor.validator,
    requests: state.requests
  }
}
const mapDispatchToProps = dispatch => {
  return {
    setEditSelectedItem: item => {
      dispatch(itemEditorActions.setEditSelectedItem(item))
    },
    setvalidator: validator => {
      dispatch(itemEditorActions.setvalidator(validator))
    },
    checknodeswitching: switchNode => {
      dispatch(itemEditorActions.checknodeswitching(switchNode))
    }
  }
}

export default withBus(
  connect(
    mapStateToProps,
    mapDispatchToProps
  )(EditorInfo)
)
